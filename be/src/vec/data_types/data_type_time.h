// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
// This file is copied from
// https://github.com/ClickHouse/ClickHouse/blob/master/src/DataTypes/DataTypeDateTime.h
// and modified by Doris

#pragma once

#include <gen_cpp/Types_types.h>
#include <stddef.h>

#include <algorithm>
#include <boost/iterator/iterator_facade.hpp>
#include <memory>
#include <string>

#include "runtime/define_primitive_type.h"
#include "vec/core/field.h"
#include "vec/core/types.h"
#include "vec/data_types/data_type.h"
#include "vec/data_types/data_type_number.h"
#include "vec/data_types/data_type_number_base.h"
#include "vec/data_types/serde/data_type_number_serde.h"
#include "vec/data_types/serde/data_type_serde.h"

namespace doris {
namespace vectorized {
class BufferWritable;
class IColumn;
} // namespace vectorized
} // namespace doris

namespace doris::vectorized {

class DataTypeTime final : public DataTypeNumberBase<Float64> {
public:
    DataTypeTime() = default;

    bool equals(const IDataType& rhs) const override;

    std::string to_string(const IColumn& column, size_t row_num) const override;
    PrimitiveType get_type_as_primitive_type() const override { return TYPE_TIME; }
    TPrimitiveType::type get_type_as_tprimitive_type() const override {
        return TPrimitiveType::TIME;
    }

    void to_string(const IColumn& column, size_t row_num, BufferWritable& ostr) const override;

    MutableColumnPtr create_column() const override;

    bool can_be_used_as_version() const override { return true; }
    bool is_summable() const override { return true; }
    bool can_be_used_in_bit_operations() const override { return true; }
    bool can_be_used_in_boolean_context() const override { return true; }
    bool can_be_inside_nullable() const override { return true; }

    bool can_be_promoted() const override { return true; }
    DataTypePtr promote_numeric_type() const override {
        using PromotedType = DataTypeNumber<NearestFieldType<Float64>>;
        return std::make_shared<PromotedType>();
    }
    DataTypeSerDeSPtr get_serde() const override {
        return std::make_shared<DataTypeNumberSerDe<Float64>>();
    };
};

} // namespace doris::vectorized
